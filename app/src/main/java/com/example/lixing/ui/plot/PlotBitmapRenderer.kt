package com.example.lixing.ui.plot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Typeface
import com.example.lixing.domain.plot.MarkArea
import com.example.lixing.domain.plot.MarkLine
import com.example.lixing.domain.plot.PlotSpec
import com.example.lixing.domain.plot.Series
import com.example.lixing.domain.plot.autoRange
import com.example.lixing.domain.plot.niceTicks
import com.example.lixing.domain.plot.prettifyPlotLabel
import com.example.lixing.domain.plot.sampleSeries
import ru.noties.jlatexmath.JLatexMathDrawable
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

    /**
     * 公式绘制器缓存：同一个图里轴标签/图例常重复出现，缓存避免反复走 TeX 解析。
     * key 是「latex + 字号」，值是已经量好尺寸的 drawable。
     */
    private val texCache = HashMap<String, JLatexMathDrawable>()

    private fun dp(v: Float) = v * density

    /**
     * 画一段文本，**自动识别 LaTeX 并排版**。
     *
     * 图上的标题 / 轴标签 / 图例都是模型自由填的字符串，它经常直接塞 `$S_c(f)$`、
     * `\frac{1}{T}`、`\sum`。以前一律按纯文本画，于是图上出现美元符号和反斜杠。
     * 现在分两条路：
     * - 能识别出 LaTeX（含 `$...$` 或反斜杠命令）→ 交给 JLatexMath 排版成公式；
     * - 否则按纯文本画（走 [prettifyPlotLabel] 降级，保持旧行为）。
     *
     * 公式排不出来（写法不合法）时**回落到纯文本**，绝不让绘图整体失败。
     * [alignCenter] 为 true 时 [x] 表示中心，否则表示左边界；[baselineY] 是文字基线。
     */
    private fun drawSmartText(
        canvas: Canvas,
        raw: String,
        x: Float,
        baselineY: Float,
        textSizePx: Float,
        color: Int,
        alignCenter: Boolean = false,
    ) {
        val tex = findLatex(raw)
        if (tex != null) {
            val drawable = texDrawable(tex, textSizePx)
            if (drawable != null) {
                val w = drawable.intrinsicWidth
                val h = drawable.intrinsicHeight
                val left = if (alignCenter) x - w / 2f else x
                // 公式以「视觉垂直居中于原文字行」的方式对齐：基线大致在行高的 70% 处
                val top = baselineY - h * 0.72f
                drawable.setBounds(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
                // 公式颜色跟随当前主题文字色
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.draw(canvas)
                return
            }
        }
        // 纯文本路径
        textPaint.textSize = textSizePx
        textPaint.color = color
        val text = prettifyPlotLabel(raw)
        val w = textPaint.measureText(text)
        canvas.drawText(text, if (alignCenter) x - w / 2f else x, baselineY, textPaint)
    }

    /** 量出 [drawSmartText] 会画的宽度，用于居中/避让。 */
    private fun smartTextWidth(raw: String, textSizePx: Float): Float {
        val tex = findLatex(raw)
        if (tex != null) {
            texDrawable(tex, textSizePx)?.let { return it.intrinsicWidth.toFloat() }
        }
        textPaint.textSize = textSizePx
        return textPaint.measureText(prettifyPlotLabel(raw))
    }

    /**
     * 从标签里提取可排版的 LaTeX 源码。
     *
     * 认两种写法：`$...$`（含 `$$...$$`）优先取中间那段；
     * 否则若整串里含反斜杠命令（`\frac`、`\sum`…）就当整串是公式。
     * 都不是则返回 null（走纯文本）。
     */
    private fun findLatex(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // 优先取 $...$ 的内容
        val dollar = Regex("""\$\$?(.+?)\$\$?""").find(trimmed)
        if (dollar != null) {
            val body = dollar.groupValues[1].trim()
            return body.ifEmpty { null }
        }
        // 没有美元符号，但含反斜杠命令：整串当公式
        if (Regex("""\\[a-zA-Z]{2,}""").containsMatchIn(trimmed)) return trimmed
        return null
    }

    /** 构造（并缓存）公式 drawable；写法非法时返回 null，由调用方回落到纯文本。 */
    private fun texDrawable(latex: String, textSizePx: Float): JLatexMathDrawable? {
        val key = "$textSizePx::$latex"
        texCache[key]?.let { return it }
        return runCatching {
            JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(theme.text)
                .build()
        }.getOrNull()?.also { texCache[key] = it }
    }

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
        val hasLegend = spec.legend || spec.series.size > 1
        // 图例改到绘图区上方（标题下面），所以上边距要把它一并算进去
        val padTop = (if (spec.title.isNotEmpty()) dp(42f) else dp(18f)) +
            (if (hasLegend) dp(22f) else 0f)
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
                // 图内文字可能是 LaTeX（模型常写 $B$、\Delta f），交给 drawSmartText 自动排版
                // 画在区域内部靠上，并与 markLine 标签错开高度，避免叠字
                drawSmartText(canvas, it, (a0 + a1) / 2, plotY + dp(30f), tickSize, theme.subText, alignCenter = true)
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
        for (t in xTicks) {
            val raw = spec.x.tickLabels[t] ?: formatTick(t)
            drawSmartText(canvas, raw, sx(t), plotY + plotH + dp(17f), tickSize, theme.subText, alignCenter = true)
        }
        for (t in yTicks) {
            val raw = spec.y.tickLabels[t] ?: formatTick(t)
            // 右对齐：以 (plotX - 8dp) 为右边界
            val w = smartTextWidth(raw, tickSize)
            drawSmartText(canvas, raw, plotX - dp(8f) - w, sy(t) + dp(4f), tickSize, theme.subText)
        }

        // ---- 9. 轴标题 ----
        val xLabel = spec.x.label
        if (xLabel.isNotEmpty()) {
            drawSmartText(canvas, xLabel, plotX + plotW / 2, height - dp(10f), labelSize, theme.text, alignCenter = true)
        }
        if (spec.y.label.isNotEmpty()) {
            val withUnit = if (spec.y.unit.isNotEmpty()) "${spec.y.label} (${spec.y.unit})" else spec.y.label
            val w = smartTextWidth(withUnit, labelSize)
            canvas.save()
            canvas.rotate(-90f, dp(14f), plotY + plotH / 2)
            drawSmartText(canvas, withUnit, dp(14f) - w / 2, plotY + plotH / 2 + labelSize / 3, labelSize, theme.text)
            canvas.restore()
        }

        // ---- 10. 标题 ----
        if (spec.title.isNotEmpty()) {
            drawSmartText(canvas, spec.title, width / 2, dp(26f), titleSize, theme.text, alignCenter = true)
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
                // 放进绘图区顶部：绘图区上方已经让给图例了，放外面会叠在一起
                drawSmartText(canvas, it, sx(x), plotY + dp(13f), tickSize, theme.subText, alignCenter = true)
            }
        }

        // ---- 12. 图例：横排在标题下方、绘图区之外 ----
        // 早先画在绘图区内部左上角，会被曲线压住（左右对称的谱线尤其明显）。
        if (hasLegend) {
            val legendY = plotY - dp(8f)
            var xx = plotX
            spec.series.forEach { s ->
                val label = s.label
                if (prettifyPlotLabel(label).isEmpty()) return@forEach
                val itemWidth = dp(26f) + smartTextWidth(label, tickSize) + dp(14f)
                // 排不下就不再画，绝不让图例伸出画布被裁成半截
                if (xx + itemWidth > plotX + plotW + dp(16f)) return@forEach
                val color = theme.seriesColors[s.colorIndex % theme.seriesColors.size]
                linePaint.color = color
                linePaint.strokeWidth = dp(2f)
                linePaint.pathEffect = null
                canvas.drawLine(xx, legendY - dp(4f), xx + dp(20f), legendY - dp(4f), linePaint)
                drawSmartText(canvas, label, xx + dp(24f), legendY, tickSize, theme.subText)
                xx += itemWidth
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
