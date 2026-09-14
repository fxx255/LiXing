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
     * `\frac{1}{T}`、`\sum`，也经常**中英混排**（「功率谱密度 $S_c(f)$」）。
     *
     * 关键约束：**公式片段里绝不能出现中文**。JLatexMath 的字体没有 CJK 字形，
     * 汉字宽度退化为 0（实测：`功率谱功率谱功率谱` 量出的宽度与单个「功率谱」
     * 完全相同，都是 12px）⇒ 所有汉字堆在同一坐标，图上文字重叠成一团。
     * 所以 [splitLabelPieces] 会把标签切成「公式 / 普通文字」交替的片段，
     * 公式交给 JLatexMath、其余交给 Canvas 文字，逐段排布。
     *
     * 公式排不出来（写法不合法）时**回落到纯文本**，绝不让绘图整体失败。
     * [alignCenter] 为 true 时 [x] 表示整段的中心，否则表示左边界；[baselineY] 是文字基线。
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
        val pieces = splitLabelPieces(raw)
        if (pieces.isEmpty()) return
        val widths = pieces.map { pieceWidth(it, textSizePx) }
        val gap = if (pieces.size > 1) dp(3f) else 0f
        val total = widths.sum() + gap * (pieces.size - 1)
        var cursorX = if (alignCenter) x - total / 2f else x
        // 标签不许画到画布外：公式过长或度量异常时宁可贴边、右边被裁，
        // 也不能整块跑到画布外面（历史上出现过「文字全挤在左上角」的观感）。
        val leftLimit = dp(2f)
        val rightLimit = canvas.width - dp(2f)
        if (cursorX < leftLimit) cursorX = leftLimit
        if (total <= rightLimit - leftLimit && cursorX + total > rightLimit) {
            cursorX = rightLimit - total
        }
        pieces.forEachIndexed { index, piece ->
            val w = widths[index]
            val latex = piece.latex
            val texWidth = if (latex != null) texWidthOrNull(latex, textSizePx) else null
            val drawable = if (latex != null && texWidth != null) texDrawable(latex, textSizePx) else null
            if (drawable != null) {
                val h = drawable.intrinsicHeight
                // 公式以「视觉垂直居中于原文字行」的方式对齐：基线大致在行高的 72% 处
                val top = baselineY - h * 0.72f
                // 用 canvas 平移定位、给 drawable 一个「原点在 (0,0)」的 bounds：
                // 位置完全由我们决定，不依赖库对 bounds.left/top 的处理方式。
                val save = canvas.save()
                canvas.translate(cursorX, top)
                drawable.setBounds(0, 0, w.toInt(), h)
                // 公式颜色跟随当前主题文字色
                drawable.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                drawable.draw(canvas)
                canvas.restoreToCount(save)
            } else {
                // 纯文本片段；公式不可用时把公式源码按纯文本画出来（带降级替换）
                drawPlainText(canvas, latex ?: piece.text, cursorX, baselineY, textSizePx, color)
            }
            cursorX += w + gap
        }
    }

    /** 画一段纯文本（含 LaTeX 命令的 Unicode 降级）。 */
    private fun drawPlainText(
        canvas: Canvas,
        raw: String,
        x: Float,
        baselineY: Float,
        textSizePx: Float,
        color: Int,
    ) {
        textPaint.textSize = textSizePx
        textPaint.color = color
        canvas.drawText(prettifyPlotLabel(raw), x, baselineY, textPaint)
    }

    /**
     * 公式排版的可用宽度；**不可用时返回 null**（构造失败、或量出的尺寸异常）。
     *
     * 尺寸异常必须挡住：零/负宽会让 `setBounds` 变成空矩形（画不出东西），
     * 宽到没边（超过 [MAX_TEX_WIDTH_EM] 个字号）则说明这条公式的度量不可信，
     * 直接把标签挤出画布。两种情况都回落成纯文本更可控。
     */
    private fun texWidthOrNull(latex: String, textSizePx: Float): Float? {
        val drawable = texDrawable(latex, textSizePx) ?: return null
        val w = drawable.intrinsicWidth
        val h = drawable.intrinsicHeight
        if (w <= 0 || h <= 0 || w > textSizePx * MAX_TEX_WIDTH_EM) return null
        return w.toFloat()
    }

    /** 单个片段的绘制宽度。 */
    private fun pieceWidth(piece: LabelPiece, textSizePx: Float): Float {
        val latex = piece.latex
        if (latex != null) {
            texWidthOrNull(latex, textSizePx)?.let { return it }
            return plainTextWidth(latex, textSizePx)
        }
        return plainTextWidth(piece.text, textSizePx)
    }

    private fun plainTextWidth(raw: String, textSizePx: Float): Float {
        textPaint.textSize = textSizePx
        return textPaint.measureText(prettifyPlotLabel(raw))
    }

    /** 量出 [drawSmartText] 会画的总宽度，用于居中/避让。 */
    private fun smartTextWidth(raw: String, textSizePx: Float): Float {
        val pieces = splitLabelPieces(raw)
        if (pieces.isEmpty()) return 0f
        val gap = if (pieces.size > 1) dp(3f) else 0f
        return pieces.sumOf { pieceWidth(it, textSizePx).toDouble() }.toFloat() +
            gap * (pieces.size - 1)
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

    /** 绘制入口（internal 便于测试注入记录型 Canvas 校验定位）。 */
    internal fun drawAll(canvas: Canvas, spec: PlotSpec, width: Float, height: Float) {
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
        // x 轴是「定义域」：以模型声明的区间为准，但要保证点集不越出视野。
        // y 轴用 balancedRange：主体完整 + 适当边距 + 极端离群点折叠（见其注释）。
        val xDataLo = allPoints.minOfOrNull { it.first } ?: -1.0
        val xDataHi = allPoints.maxOfOrNull { it.first } ?: 1.0
        val (xLo, xHi) = conservativeRange(xDataLo, xDataHi, spec.x.min, spec.x.max)
        val yRange = balancedRange(allPoints.map { it.second }, spec.y.min, spec.y.max)
        val yLo = yRange.lo
        val yHi = yRange.hi
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
        // 裁剪到绘图区：被折叠到范围外的极端点不应画到框外（会压住轴标签）。
        val seriesSave = canvas.save()
        canvas.clipRect(plotX, plotY, plotX + plotW, plotY + plotH)
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
        canvas.restoreToCount(seriesSave)

        // ---- 7. 坐标轴 ----
        linePaint.color = theme.axis
        linePaint.strokeWidth = dp(1f)
        linePaint.pathEffect = null
        canvas.drawLine(plotX, plotY + plotH, plotX + plotW, plotY + plotH, linePaint)
        canvas.drawLine(plotX, plotY, plotX, plotY + plotH, linePaint)

        // ---- 7b. 断轴标记：有数据被折叠到范围外时，在竖直轴上画「断口」 ----
        if (yRange.foldedHigh) drawAxisBreak(canvas, plotX, plotY + dp(9f))
        if (yRange.foldedLow) drawAxisBreak(canvas, plotX, plotY + plotH - dp(9f))

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

    /**
     * 断轴标记：两条短斜线（制图惯例符号）画在竖直坐标轴上，表示「轴在此处被折叠，
     * 仍有超出范围的数据被压缩显示」——保证数据不被无声丢弃。
     */
    private fun drawAxisBreak(canvas: Canvas, axisX: Float, y: Float) {
        linePaint.color = theme.axis
        linePaint.strokeWidth = dp(1.2f)
        linePaint.pathEffect = null
        val w = dp(5f)
        val h = dp(4f)
        val gap = dp(2.6f)
        canvas.drawLine(axisX - w, y + h, axisX + w, y - h, linePaint)
        canvas.drawLine(axisX - w, y + h + gap, axisX + w, y - h + gap, linePaint)
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

/** 标签片段：要么是交给 JLatexMath 的公式（[latex]），要么是直接画的普通文字（[text]）。 */
internal data class LabelPiece(val text: String = "", val latex: String? = null)

/** 公式宽度的可信上限（按字号计）：超过这么多 em 说明度量不可信，回落纯文本。 */
private const val MAX_TEX_WIDTH_EM = 40f

/**
 * 坐标范围（可带折叠标记）。
 *
 * [foldedLow]/[foldedHigh] 为 true 表示该侧有数据落在范围之外、被**折叠**到边界：
 * 曲线在边界处被裁断，并在轴上画出断轴标记，提示「这里还有数据，只是被压缩显示」。
 */
internal data class AxisRange(
    val lo: Double,
    val hi: Double,
    val foldedLow: Boolean = false,
    val foldedHigh: Boolean = false,
)

/**
 * 平衡的坐标范围：**主体数据完整显示 + 适当边距 + 极端离群点折叠**。
 *
 * 背景：早先「模型给什么范围就用什么范围」会让曲线极值贴框（谷底压在坐标轴底线上）；
 * 而改成「必须覆盖全部数据」又走到另一个极端——个别离群点会把主体曲线压成一条线。
 * 现在折中（[fenceFactor] 为 Tukey 围栏系数）：
 * 1. 用 **Tukey 规则**识别离群点：`Q1 - k·IQR` / `Q3 + k·IQR` 之外的点才算离群；
 *    **没有离群点时主体 = 全量数据**（不像分位数那样把正常的极值也裁掉）；
 * 2. 模型显式给的范围优先，但**至少要覆盖主体范围**（否则视为把主体裁掉，会扩展它）；
 * 3. 最后统一加 [marginRatio] 边距，保证曲线不贴框线；
 * 4. 落在最终范围外的数据**不撑大坐标轴**，而是折叠到边界（由调用方裁剪 + 画断轴标记）。
 */
internal fun balancedRange(
    values: List<Double?>,
    specLo: Double?,
    specHi: Double?,
    marginRatio: Double = 0.06,
    fenceFactor: Double = 1.5,
): AxisRange {
    val finite = values.filter { it != null && it.isFinite() }.map { it!! }.sorted()
    if (finite.isEmpty()) return AxisRange(specLo ?: -1.0, specHi ?: 1.0)

    val dataLo = finite.first()
    val dataHi = finite.last()
    // Tukey 围栏：IQR 为 0（大量重复值，如阶梯/常数）时不裁剪，直接用完整范围
    val q1 = quantile(finite, 0.25)
    val q3 = quantile(finite, 0.75)
    val iqr = q3 - q1
    val lowerFence = q1 - fenceFactor * iqr
    val upperFence = q3 + fenceFactor * iqr
    val coreLo = finite.firstOrNull { it >= lowerFence } ?: dataLo
    val coreHi = finite.lastOrNull { it <= upperFence } ?: dataHi
    val (useLo, useHi) = if (iqr <= 0.0 || coreHi <= coreLo) dataLo to dataHi else coreLo to coreHi

    var lo = specLo ?: useLo
    var hi = specHi ?: useHi
    // 模型范围不能裁掉主体（极少数情况下它给的窗口会把主要特征切掉）
    if (lo > useLo) lo = useLo
    if (hi < useHi) hi = useHi

    if (hi - lo <= 0.0) {
        val pad = (abs(lo) + 1.0) * 0.1
        lo -= pad
        hi += pad
    }
    // 边距按**主体范围**四周留白：极端点被折叠时，贴框的必须是「主体的边缘」而不是
    // 离群点，所以用主体跨度算边距、并且只做外扩（minOf/maxOf）——模型给的更宽窗口不会被缩掉。
    val coreMargin = ((useHi - useLo).takeIf { it > 0.0 } ?: 0.0) * marginRatio
    if (coreMargin > 0.0) {
        lo = minOf(lo, useLo - coreMargin)
        hi = maxOf(hi, useHi + coreMargin)
    }
    return AxisRange(lo, hi, foldedLow = dataLo < lo, foldedHigh = dataHi > hi)
}

/** 线性插值分位数（[sorted] 必须已升序）。 */
private fun quantile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return 0.0
    val idx = (sorted.size - 1) * p.coerceIn(0.0, 1.0)
    val lower = idx.toInt()
    val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
    val frac = idx - lower
    return sorted[lower] + (sorted[upper] - sorted[lower]) * frac
}

/**
 * 保守的坐标范围：**保证覆盖 [dataLo]..[dataHi]，模型给的范围只在更宽时生效**。
 *
 * 专用于 x 轴（定义域）：以模型声明的区间为准，但点集不得越出视野被画到框外。
 * y 轴请用 [balancedRange]（带离群点折叠与边距）。
 */
internal fun conservativeRange(
    dataLo: Double,
    dataHi: Double,
    specLo: Double?,
    specHi: Double?,
): Pair<Double, Double> = minOf(specLo ?: dataLo, dataLo) to maxOf(specHi ?: dataHi, dataHi)

/** `$...$` / `$$...$$` 分组（非贪婪，取第一对定界符之间的内容）。 */
private val DOLLAR_LATEX_GROUP = Regex("""\$\$?(.+?)\$\$?""")

/** 反斜杠命令（`\frac`、`\sum`…）：没有美元符号时用它判断整串是不是公式。 */
private val LATEX_COMMAND = Regex("""\\[a-zA-Z]{2,}""")

/**
 * CJK 及全角标点区间。这类字符**不能**交给 JLatexMath：
 * 其内置字体没有中文字形，字符宽度退化为 0（实测「功率谱」与
 * 「功率谱功率谱功率谱」量出的宽度同为 12px）⇒ 所有汉字堆叠在同一坐标，
 * 图上文字重叠成一团（用户反馈的「文字全挤在一起」）。
 */
private val CJK_RANGE = Regex("""[\u2E80-\u9FFF\uF900-\uFAFF\uFE30-\uFE4F\uFF00-\uFFEF\u3000-\u303F]""")

/** 该串是否含中文字符（含 CJK 标点与全角形式）。 */
internal fun containsCjk(text: String): Boolean = CJK_RANGE.containsMatchIn(text)

/**
 * 把标签切成「公式 / 普通文字」交替的片段。
 *
 * 规则：
 * 1. `$...$` 分组 → 公式片段；**公式体内含中文时退回普通文字**（否则会叠成一团）；
 * 2. 分组之外的内容 → 普通文字片段（含 LaTeX 命令时由 `prettifyPlotLabel` 降级成 Unicode）；
 * 3. 整串没有美元分组、也不含中文、但含反斜杠命令（如 `\frac{N_0}{2}`）→ 整串当公式；
 * 4. 其余情况 → 一个普通文字片段（保持旧行为）。
 *
 * 这样「功率谱密度 $S_c(f)$」会拆成两段依次排布，中文与公式各自清晰可读。
 */
internal fun splitLabelPieces(raw: String): List<LabelPiece> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyList()

    val pieces = mutableListOf<LabelPiece>()
    var cursor = 0
    for (match in DOLLAR_LATEX_GROUP.findAll(trimmed)) {
        val before = trimmed.substring(cursor, match.range.first).trim()
        if (before.isNotEmpty()) pieces += LabelPiece(text = before)
        val body = match.groupValues[1].trim()
        when {
            body.isEmpty() -> Unit
            containsCjk(body) -> pieces += LabelPiece(text = body)
            else -> pieces += LabelPiece(latex = body)
        }
        cursor = match.range.last + 1
    }
    val tail = trimmed.substring(cursor).trim()
    if (tail.isNotEmpty()) pieces += LabelPiece(text = tail)

    if (pieces.isEmpty()) return listOf(LabelPiece(text = trimmed))
    if (pieces.size == 1 && pieces[0].latex == null &&
        !containsCjk(trimmed) && LATEX_COMMAND.containsMatchIn(trimmed)
    ) {
        return listOf(LabelPiece(latex = trimmed))
    }
    return pieces
}
