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
    private const val LABEL_CANVAS_PADDING = 28f

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
        layout.nodes.forEach { drawNode(canvas, it, layout.edges) }
        // Labels are laid on clear sections of their routed polyline.  Keep a
        // small occupancy list so adjacent branch labels cannot paint over
        // one another or over a node.  The list uses logical (pre-scale) px.
        val occupiedLabels = annotationOccupancy(layout)
        layout.edges.forEach {
            drawEdgeLabel(canvas, it, layout.nodes, occupiedLabels, layout.width, layout.height)
        }
        return bitmap
    }

    /** Reserve marker/sign space before choosing edge-label locations. */
    private fun annotationOccupancy(layout: DiagramLayoutResult): MutableList<RectF> {
        val occupied = mutableListOf<RectF>()
        layout.nodes.filter { it.node.shape == DiagramNodeShape.JUNCTION }.forEach { box ->
            val marker = DiagramLayout.label(box.node)
            if (marker.lines.isNotEmpty()) {
                occupied += RectF(
                    box.centerX - marker.width / 2f - 4f,
                    box.y - marker.height - 9f,
                    box.centerX + marker.width / 2f + 4f,
                    box.y - 1f,
                )
            }
        }
        layout.nodes.filter { it.node.shape == DiagramNodeShape.SUM }.forEach { box ->
            occupied += RectF(box.centerX - 14f, box.y - 26f, box.centerX + 14f, box.y + 2f)
            occupied += RectF(box.centerX - 14f, box.y + box.height - 2f,
                box.centerX + 14f, box.y + box.height + 30f)
        }
        return occupied
    }

    private fun drawNode(canvas: Canvas, box: NodeBox, edges: List<EdgeRoute>) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val rect = RectF(box.x, box.y, box.x + box.width, box.y + box.height)
        when (box.node.shape) {
            DiagramNodeShape.MIXER, DiagramNodeShape.SUM -> {
                canvas.drawOval(rect, fill)
                canvas.drawOval(rect, stroke)
                if (box.node.shape == DiagramNodeShape.SUM) {
                    val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = INK; style = Paint.Style.STROKE; strokeWidth = 1.35f
                    }
                    canvas.drawLine(box.centerX, box.y + 8f, box.centerX, box.y + box.height - 8f, cross)
                    canvas.drawLine(box.x + 8f, box.centerY, box.x + box.width - 8f, box.centerY, cross)
                    drawSumInputSigns(canvas, box, edges)
                } else {
                    val glyph = box.node.glyph ?: "×"
                    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 29f; textAlign = Paint.Align.CENTER }
                    canvas.drawText(glyph, box.centerX, box.centerY - (text.ascent() + text.descent()) / 2, text)
                }
            }
            DiagramNodeShape.JUNCTION -> {
                canvas.drawCircle(box.centerX, box.centerY, box.width / 2,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK })
                val marker = DiagramLayout.label(box.node)
                if (marker.lines.isNotEmpty()) {
                    drawText(canvas, marker, box.centerX - marker.width / 2,
                        box.y - marker.height - 5f, DiagramTextRole.EDGE_LABEL, centered = true)
                }
            }
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

    private fun drawSumInputSigns(canvas: Canvas, box: NodeBox, edges: List<EdgeRoute>) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; textSize = 17f; textAlign = Paint.Align.CENTER }
        edges.filter { it.edge.to == box.node.id }.forEach { route ->
            val sign = route.edge.polarity ?: when (route.endPort) {
                DiagramPort.TOP -> "+"
                DiagramPort.BOTTOM -> "−"
                else -> null
            } ?: return@forEach
            val (x, y) = when (route.endPort) {
                DiagramPort.TOP -> box.centerX to box.y - 8f
                DiagramPort.BOTTOM -> box.centerX to box.y + box.height + 22f
                DiagramPort.LEFT -> box.x - 12f to box.centerY - 6f
                DiagramPort.RIGHT -> box.x + box.width + 12f to box.centerY - 6f
                DiagramPort.AUTO -> return@forEach
            }
            canvas.drawText(sign, x, y, paint)
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

    private fun drawEdgeLabel(
        canvas: Canvas,
        route: EdgeRoute,
        nodes: List<NodeBox>,
        occupied: MutableList<RectF>,
        canvasWidth: Float,
        canvasHeight: Float,
    ) {
        val label = route.edge.label?.trim()?.takeIf { it.isNotBlank() }
            ?.takeUnless { isDuplicateMarker(it, route, nodes) }
            ?: return
        val segment = route.points.zipWithNext().maxByOrNull { (a, b) ->
            kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)
        } ?: return
        val (a, b) = segment
        val text = DiagramText.layout(label, DiagramTextRole.EDGE_LABEL, 140f)
        val horizontal = kotlin.math.abs(a.x - b.x) >= kotlin.math.abs(a.y - b.y)
        val midX = (a.x + b.x) / 2f
        val midY = (a.y + b.y) / 2f
        val candidates = if (horizontal) {
            listOf(
                RectF(midX - text.width / 2f, midY - text.height - 9f,
                    midX + text.width / 2f, midY - 9f),
                RectF(midX - text.width / 2f, midY + 9f,
                    midX + text.width / 2f, midY + 9f + text.height),
            )
        } else {
            listOf(
                RectF(midX + 9f, midY - text.height / 2f,
                    midX + 9f + text.width, midY + text.height / 2f),
                RectF(midX - 9f - text.width, midY - text.height / 2f,
                    midX - 9f, midY + text.height / 2f),
            )
        }
        fun clamped(rect: RectF): RectF {
            val dx = when {
                rect.left < LABEL_CANVAS_PADDING -> LABEL_CANVAS_PADDING - rect.left
                rect.right > canvasWidth - LABEL_CANVAS_PADDING ->
                    canvasWidth - LABEL_CANVAS_PADDING - rect.right
                else -> 0f
            }
            val dy = when {
                rect.top < LABEL_CANVAS_PADDING -> LABEL_CANVAS_PADDING - rect.top
                rect.bottom > canvasHeight - LABEL_CANVAS_PADDING ->
                    canvasHeight - LABEL_CANVAS_PADDING - rect.bottom
                else -> 0f
            }
            return RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
        }
        fun intersects(a: RectF, b: RectF): Boolean =
            a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        fun occupiedByNode(rect: RectF): Boolean = nodes.any { box ->
            intersects(rect, RectF(box.x - 4f, box.y - 4f,
                box.x + box.width + 4f, box.y + box.height + 4f))
        }
        val chosen = candidates.asSequence()
            .map(::clamped)
            .firstOrNull { rect -> !occupiedByNode(rect) && occupied.none { intersects(rect, it) } }
            ?: return
        occupied += RectF(chosen)
        val x = chosen.left
        val y = chosen.top
        // A safe placement gets an opaque background to keep the wire out of
        // the glyphs.  If no safe slot exists, omit this optional inline label
        // rather than painting text over a node or another annotation.
        canvas.drawRect(chosen.left - 3f, chosen.top - 2f,
            chosen.right + 3f, chosen.bottom + 2f,
            Paint().apply { color = Color.WHITE })
        drawText(canvas, text, x, y, DiagramTextRole.EDGE_LABEL, centered = true)
    }

    /**
     * Test-point letters and output names are sometimes emitted twice: once
     * as a node label and once as the edge label.  Keep the semantic node
     * annotation and suppress only the duplicate short marker; signal labels
     * such as carrier formulas remain visible.
     */
    private fun isDuplicateMarker(label: String, route: EdgeRoute, nodes: List<NodeBox>): Boolean {
        val marker = label.trim().replace("−", "-")
        val isTestMarker = marker.length == 1 && marker[0] in 'A'..'G'
        val source = nodes.firstOrNull { it.node.id == route.edge.from }?.node
        val target = nodes.firstOrNull { it.node.id == route.edge.to }?.node
        fun normalized(value: String?): String = value.orEmpty()
            .trim()
            .replace(Regex("\\s+"), "")
            .replace("−", "-")
        val edgeText = normalized(marker)
        val matchesNode = listOf(source, target).any { node ->
            val text = normalized(node?.label)
            text == edgeText || (isTestMarker && text.contains(edgeText))
        }
        return matchesNode || (isTestMarker && listOf(source, target).any { it?.role?.equals("test_${marker.lowercase()}") == true })
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
