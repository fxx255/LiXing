package com.example.lixing.ui.diagram

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.example.lixing.domain.diagram.*
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/** Textbook-style local diagrams. The stored image keeps its natural aspect ratio. */
object DiagramRenderer {
    private const val INK = 0xFF202B38.toInt()
    private const val MUTED = 0xFF536274.toInt()

    fun render(spec: DiagramSpec, widthPx: Int = 4096, heightPx: Int = 4096): Bitmap =
        renderLayout(DiagramLayout.layout(spec), widthPx, heightPx)

    fun renderLayout(layout: DiagramLayoutResult, widthPx: Int, heightPx: Int): Bitmap {
        val scale = minOf(3f, widthPx / layout.width, heightPx / layout.height,
            sqrt(8_000_000f / (layout.width * layout.height)))
        require(scale > 0)
        val bitmap = Bitmap.createBitmap((layout.width * scale).toInt().coerceAtLeast(1),
            (layout.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        drawText(canvas, DiagramText.layout(layout.title, DiagramTextRole.TITLE, 600f),
            28f, 28f, DiagramTextRole.TITLE)
        layout.edges.forEach { drawEdge(canvas, it) }
        layout.nodes.forEach { drawNode(canvas, it) }
        // Labels are laid on clear sections of their routed polyline.
        layout.edges.forEach { drawEdgeLabel(canvas, it) }
        return bitmap
    }

    private fun drawNode(canvas: Canvas, box: NodeBox) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val rect = RectF(box.x, box.y, box.x + box.width, box.y + box.height)
        when (box.node.shape) {
            DiagramNodeShape.MIXER, DiagramNodeShape.SUM -> {
                canvas.drawOval(rect, fill)
                canvas.drawOval(rect, stroke)
                val glyph = box.node.glyph ?: if (box.node.shape == DiagramNodeShape.SUM) "+" else "×"
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 29f; textAlign = Paint.Align.CENTER }
                canvas.drawText(glyph, box.centerX, box.centerY - (text.ascent() + text.descent()) / 2, text)
            }
            DiagramNodeShape.JUNCTION -> canvas.drawCircle(box.centerX, box.centerY, box.width / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
            else -> {
                if (box.node.shape == DiagramNodeShape.BLOCK) {
                    canvas.drawRoundRect(rect, 4f, 4f, fill)
                    canvas.drawRoundRect(rect, 4f, 4f, stroke)
                }
                val main = DiagramLayout.label(box.node)
                val sub = DiagramLayout.subLabel(box.node)
                val gap = if (sub.height > 0) 4f else 0f
                val top = box.centerY - (main.height + sub.height + gap) / 2
                drawText(canvas, main, box.centerX - main.width / 2, top, DiagramTextRole.LABEL, centered = true)
                drawText(canvas, sub, box.centerX - sub.width / 2, top + main.height + gap,
                    DiagramTextRole.SUB_LABEL, centered = true)
            }
        }
    }

    private fun drawEdge(canvas: Canvas, route: EdgeRoute) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (route.edge.dashed) MUTED else INK
            style = Paint.Style.STROKE; strokeWidth = 1.6f
            if (route.edge.dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val path = Path().apply {
            route.points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        canvas.drawPath(path, paint)
        val previous = route.points.dropLast(1).lastOrNull() ?: route.start
        val angle = Math.toDegrees(atan2((route.end.y - previous.y).toDouble(),
            (route.end.x - previous.x).toDouble())).toFloat()
        canvas.save()
        canvas.translate(route.end.x, route.end.y)
        canvas.rotate(angle)
        canvas.drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(-9f, -4f); lineTo(-9f, 4f); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color })
        canvas.restore()
    }

    private fun drawEdgeLabel(canvas: Canvas, route: EdgeRoute) {
        val label = route.edge.label?.takeIf { it.isNotBlank() } ?: return
        val segment = route.points.zipWithNext().maxByOrNull { (a, b) ->
            kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)
        } ?: return
        val (a, b) = segment
        val text = DiagramText.layout(label, DiagramTextRole.EDGE_LABEL, 140f)
        val x = (a.x + b.x) / 2 - text.width / 2
        val y = (a.y + b.y) / 2 - text.height / 2
        canvas.drawRect(x - 3, y - 2, x + text.width + 3, y + text.height + 2,
            Paint().apply { color = Color.WHITE })
        drawText(canvas, text, x, y, DiagramTextRole.EDGE_LABEL, centered = true)
    }

    private fun drawText(canvas: Canvas, block: DiagramTextBlock, x: Float, top: Float,
                         role: DiagramTextRole, centered: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (role == DiagramTextRole.SUB_LABEL) MUTED else INK
            textSize = role.fontSizePx
        }
        var y = top
        for (line in block.lines) {
            var cursor = x + if (centered) (block.width - line.width) / 2 else 0f
            for (run in line.runs) {
                val drawable = run.formula
                if (drawable != null) {
                    canvas.save()
                    canvas.translate(cursor, y + (line.height - run.height) / 2)
                    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    drawable.draw(canvas)
                    canvas.restore()
                } else {
                    val baseline = y + (line.height - run.height) / 2 - paint.fontMetrics.ascent
                    canvas.drawText(run.text, cursor, baseline, paint)
                }
                cursor += run.width
            }
            y += line.height + 4f
        }
    }
}
