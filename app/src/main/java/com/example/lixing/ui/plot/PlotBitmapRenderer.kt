package com.example.lixing.ui.plot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.example.lixing.domain.plot.MarkArea
import com.example.lixing.domain.plot.MarkLine
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import com.example.lixing.domain.plot.autoRange
import com.example.lixing.domain.plot.niceTicks
import com.example.lixing.domain.plot.sampleSeries
import kotlin.math.abs
import kotlin.math.min

/**
 * 结构化参数 → 位图（Android Canvas 版）。
 *
 * 与 PC 上用 Java2D 验证过的版本是同一套绘制流程，差别只在绘图 API：
 * BufferedImage→Bitmap、Graphics2D→Canvas、Path2D→Path、Font→Paint。
 * domain 层的模型、刻度算法、表达式采样、坐标变换全部原样复用。
 *
 * 所有尺寸按 density 缩放，保证不同屏幕观感一致。
 */
class PlotBitmapRenderer(
    private val density: Float,
    private val theme: Theme = Theme(),
) {

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
    )

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun dp(v: Float) = v * density

    fun render(spec: PlotSpec, widthPx: Int, heightPx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(theme.background)
        drawAll(canvas, spec, widthPx.toFloat(), heightPx.toFloat())
        return bitmap
    }

    private fun drawAll(canvas: Canvas, spec: PlotSpec, width: Float, height: Float) {
        val titleSize = dp(15f)
        val labelSize = dp(12.5f)
        val tickSize = dp(11.5f)

        // ---- 1. 采样：表达式与点集在这里汇合，之后只认点集 ----
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
        val padLeft = dp(62f)
        val padRight = dp(18f)
        val padTop = if (spec.title.isNotEmpty()) dp(46f) else dp(22f)
        val padBottom = dp(46f)
        val plotX = padLeft
        val plotY = padTop
        val plotW = width - padLeft - padRight
        val plotH = height - padTop - padBottom

        fun sx(x: Double): Float = plotX + ((x - xLo) / spanX * plotW).toFloat()
        fun sy(y: Double): Float = plotY + plotH - ((y - yLo) / spanY * plotH).toFloat()

        val xTicks = spec.x.ticks ?: niceTicks(xLo, xHi, 5)
        val yTicks = spec.y.ticks ?: niceTicks(yLo, yHi, 4)

        // ---- 4. markArea（垫在网格下面）----
        for (area in spec.markAreas) {
            val a0 = sx(area.x0)
            val a1 = sx(area.x1)
            fillPaint.color = theme.markArea
            fillPaint.pathEffect = null
            canvas.drawRect(min(a0, a1), plotY, maxOf(a0, a1), plotY + plotH, fillPaint)
            area.label?.let {
                textPaint.textSize = tickSize
                textPaint.color = theme.subText
                val w = textPaint.measureText(it)
                // 画在区域内部顶部：markLine 标签占的是绘图区上方，两者同高会叠成 "fBc"
                canvas.drawText(it, (a0 + a1) / 2 - w / 2, plotY + dp(18f), textPaint)
            }
        }

        // ---- 5. 网格 ----
        linePaint.color = theme.grid
        linePaint.strokeWidth = dp(0.8f)
        linePaint.pathEffect = null
        for (t in xTicks) {
            val px = sx(t)
            canvas.drawLine(px, plotY, px, plotY + plotH, linePaint)
        }
        for (t in yTicks) {
            val py = sy(t)
            canvas.drawLine(plotX, py, plotX + plotW, py, linePaint)
        }

        // ---- 6. 序列：面积 → 折线 ----
        spec.series.forEachIndexed { index, s ->
            val pts = sampled[index]
            if (pts.isEmpty()) return@forEachIndexed
            val base = theme.seriesColors[s.colorIndex % theme.seriesColors.size]

            if (s.fill) {
                fillPaint.color = withAlpha(base, 38)
                fillPaint.pathEffect = null
                fillArea(canvas, pts, ::sx, ::sy, plotY + plotH)
            }
            if (s.style == "marker") {
                fillPaint.color = withAlpha(base, (255 * s.opacity).toInt())
                fillPaint.pathEffect = null
                val r = dp(3f)
                for ((x, y) in pts) {
                    if (y == null || !y.isFinite()) continue
                    canvas.drawCircle(sx(x), sy(y), r, fillPaint)
                }
            } else {
                drawPolyline(canvas, pts, ::sx, ::sy, withAlpha(base, (255 * s.opacity).toInt()), s.width, s.style == "dashed")
            }
        }

        // ---- 7. 坐标轴 ----
        linePaint.color = theme.axis
        linePaint.strokeWidth = dp(1f)
        linePaint.pathEffect = null
        canvas.drawLine(plotX, plotY + plotH, plotX + plotW, plotY + plotH, linePaint)
        canvas.drawLine(plotX, plotY, plotX, plotY + plotH, linePaint)

        // ---- 8. 刻度与标签 ----
        textPaint.textSize = tickSize
        textPaint.color = theme.subText
        for (t in xTicks) {
            val label = spec.x.tickLabels[t] ?: formatTick(t)
            val w = textPaint.measureText(label)
            canvas.drawText(label, sx(t) - w / 2, plotY + plotH + dp(17f), textPaint)
        }
        for (t in yTicks) {
            val label = spec.y.tickLabels[t] ?: formatTick(t)
            val w = textPaint.measureText(label)
            canvas.drawText(label, plotX - dp(8f) - w, sy(t) + dp(4f), textPaint)
        }

        // ---- 9. 轴标题 ----
        textPaint.textSize = labelSize
        textPaint.color = theme.text
        if (spec.x.label.isNotEmpty()) {
            val w = textPaint.measureText(spec.x.label)
            canvas.drawText(spec.x.label, plotX + plotW / 2 - w / 2, height - dp(10f), textPaint)
        }
        if (spec.y.label.isNotEmpty()) {
            val label = if (spec.y.unit.isNotEmpty()) "${spec.y.label} (${spec.y.unit})" else spec.y.label
            val w = textPaint.measureText(label)
            canvas.save()
            canvas.rotate(-90f, dp(14f), plotY + plotH / 2)
            canvas.drawText(label, dp(14f) - w / 2, plotY + plotH / 2 + labelSize / 3, textPaint)
            canvas.restore()
        }

        // ---- 10. 标题 ----
        if (spec.title.isNotEmpty()) {
            textPaint.textSize = titleSize
            textPaint.color = theme.text
            val w = textPaint.measureText(spec.title)
            canvas.drawText(spec.title, (width - w) / 2, dp(26f), textPaint)
        }

        // ---- 11. markLine ----
        for (line in spec.markLines) {
            val x = line.x ?: continue
            linePaint.color = theme.markLine
            linePaint.strokeWidth = dp(0.9f)
            linePaint.pathEffect = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
            canvas.drawLine(sx(x), plotY, sx(x), plotY + plotH, linePaint)
            linePaint.pathEffect = null
            line.label?.let {
                textPaint.textSize = tickSize
                textPaint.color = theme.subText
                val w = textPaint.measureText(it)
                canvas.drawText(it, sx(x) - w / 2, plotY - dp(6f), textPaint)
            }
        }

        // ---- 12. 图例 ----
        if (spec.legend || spec.series.size > 1) {
            textPaint.textSize = tickSize
            var yy = plotY + dp(12f)
            spec.series.forEach { s ->
                if (s.label.isEmpty()) return@forEach
                val color = theme.seriesColors[s.colorIndex % theme.seriesColors.size]
                linePaint.color = color
                linePaint.strokeWidth = dp(2f)
                canvas.drawLine(plotX + dp(12f), yy - dp(4f), plotX + dp(34f), yy - dp(4f), linePaint)
                textPaint.color = theme.subText
                canvas.drawText(s.label, plotX + dp(40f), yy, textPaint)
                yy += dp(17f)
            }
        }
    }

    /** 画折线：null 处断开——发散点（1/x 在 0 等）绝不能连成一条竖直长线。 */
    private fun drawPolyline(
        canvas: Canvas,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
        color: Int,
        width: Float,
        dashed: Boolean,
    ) {
        linePaint.color = color
        linePaint.strokeWidth = dp(width)
        linePaint.strokeCap = Paint.Cap.ROUND
        linePaint.strokeJoin = Paint.Join.ROUND
        linePaint.pathEffect = if (dashed) DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f) else null

        val path = Path()
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
        canvas.drawPath(path, linePaint)
        linePaint.pathEffect = null
    }

    /** 面积填充：按连续段分别闭合到基线。 */
    private fun fillArea(
        canvas: Canvas,
        pts: List<Pair<Double, Double?>>,
        sx: (Double) -> Float,
        sy: (Double) -> Float,
        baseline: Float,
    ) {
        val path = Path()
        var segment = mutableListOf<Pair<Float, Float>>()
        var hasSegment = false

        fun flush() {
            if (segment.size >= 2) {
                path.moveTo(segment.first().first, baseline)
                for (p in segment) path.lineTo(p.first, p.second)
                path.lineTo(segment.last().first, baseline)
                path.close()
                hasSegment = true
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
        if (hasSegment) canvas.drawPath(path, fillPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )

    private fun formatTick(v: Double): String = when {
        abs(v - v.toLong()) < 1e-9 -> v.toLong().toString()
        abs(v) < 1e-3 -> String.format("%.2e", v)
        abs(v) >= 10000 -> String.format("%.0f", v)
        else -> String.format("%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }
}
