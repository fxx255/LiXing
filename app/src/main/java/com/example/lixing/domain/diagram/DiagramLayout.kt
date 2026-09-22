package com.example.lixing.domain.diagram

import kotlin.math.abs
import kotlin.math.max

/** A horizontal main chain with auxiliary sources and branches in separate cells. */
object DiagramLayout {
    private const val GAP_X = 64f
    private const val GAP_Y = 64f
    private const val PADDING = 28f
    private data class Cell(val col: Int, val row: Int)

    fun layout(spec: DiagramSpec): DiagramLayoutResult {
        require(spec.direction == DiagramDirection.LR) { "Only LR diagrams are supported" }
        val grid = grid(spec)
        val sizes = spec.nodes.associate { it.id to sizeOf(it) }
        val widths = grid.values.map { it.col }.distinct().associateWith { col ->
            spec.nodes.filter { grid.getValue(it.id).col == col }.maxOf { sizes.getValue(it.id).width }
        }
        val heights = grid.values.map { it.row }.distinct().associateWith { row ->
            spec.nodes.filter { grid.getValue(it.id).row == row }.maxOf { sizes.getValue(it.id).height }
        }
        val title = DiagramText.layout(spec.title, DiagramTextRole.TITLE, 600f)
        val edgeLabels = spec.edges.map { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f) }
        val gapX = max(GAP_X, (edgeLabels.maxOfOrNull { it.width } ?: 0f) + 24)
        val gapY = max(GAP_Y, (edgeLabels.maxOfOrNull { it.height } ?: 0f) + 24)
        val x = starts(widths, PADDING, gapX)
        val y = starts(heights, PADDING + title.height + (if (spec.title.isBlank()) 0f else 16f), gapY)
        val boxes = spec.nodes.map { node ->
            val cell = grid.getValue(node.id)
            val size = sizes.getValue(node.id)
            NodeBox(node,
                x.getValue(cell.col) + (widths.getValue(cell.col) - size.width) / 2,
                y.getValue(cell.row) + (heights.getValue(cell.row) - size.height) / 2,
                size.width, size.height, cell.col, cell.row)
        }
        val byId = boxes.associateBy { it.node.id }
        val routes = spec.edges.map { edge ->
            val from = byId.getValue(edge.from)
            val to = byId.getValue(edge.to)
            val departure = resolvePort(edge.fromPort, from, to)
            val arrival = resolvePort(edge.toPort, to, from)
            val start = anchor(from, departure)
            val end = anchor(to, arrival)
            EdgeRoute(edge, start, end, departure, arrival,
                DiagramRouting.route(start, end, departure, arrival, boxes))
        }
        val maxX = max(boxes.maxOfOrNull { it.x + it.width } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.x } ?: 0f)
        val maxY = max(boxes.maxOfOrNull { it.y + it.height } ?: 0f,
            routes.flatMap { it.points }.maxOfOrNull { it.y } ?: 0f)
        return DiagramLayoutResult(
            maxOf(maxX + PADDING, title.width + PADDING * 2, DiagramMetrics.MIN_WIDTH),
            maxOf(maxY + PADDING, DiagramMetrics.MIN_HEIGHT), spec.title, boxes, routes,
            boxes.groupBy { it.row }.mapValues { (_, row) -> row.map { it.node.id } })
    }

    private fun grid(spec: DiagramSpec): Map<String, Cell> {
        // Vertical ports identify auxiliary branches, even if the carrier appears first.
        val horizontal = spec.edges.filter {
            it.fromPort !in setOf(DiagramPort.TOP, DiagramPort.BOTTOM) &&
                it.toPort !in setOf(DiagramPort.TOP, DiagramPort.BOTTOM)
        }
        val outgoing = horizontal.groupBy { it.from }
        // Memoize paths to keep even dense diagrams bounded. A cycle is cut at the active node.
        val memo = mutableMapOf<String, List<String>>()
        fun longest(id: String, active: Set<String>): List<String> {
            if (id in active) return emptyList()
            memo[id]?.let { return it }
            val tail = outgoing[id].orEmpty().map { longest(it.to, active + id) }
                .maxByOrNull { it.size }.orEmpty()
            return (listOf(id) + tail).distinct().also { memo[id] = it }
        }
        val roots = spec.nodes.filter { node -> horizontal.none { it.to == node.id } }
        val chain = roots.ifEmpty { spec.nodes.take(1) }.map { longest(it.id, emptySet()) }
            .maxByOrNull { it.size }.orEmpty()
        val grid = linkedMapOf<String, Cell>()
        fun place(id: String, suggested: Cell) {
            val node = spec.nodes.first { it.id == id }
            var cell = Cell(node.column ?: suggested.col, node.row ?: suggested.row)
            while (cell in grid.values) cell = cell.copy(row = cell.row + 1)
            grid[id] = cell
        }
        var nextColumn = 0
        chain.forEach { id ->
            place(id, Cell(nextColumn, 0))
            nextColumn = grid.getValue(id).col + 1
        }
        val pending = spec.nodes.filter { it.id !in grid }.toMutableList()
        while (pending.isNotEmpty()) {
            val node = pending.firstOrNull { n -> spec.edges.any {
                (it.from == n.id && it.to in grid) || (it.to == n.id && it.from in grid)
            } } ?: pending.first()
            val incoming = spec.edges.firstOrNull { it.to == node.id && it.from in grid }
            val outgoingEdge = spec.edges.firstOrNull { it.from == node.id && it.to in grid }
            val suggested = when {
                incoming != null -> {
                    val base = grid.getValue(incoming.from)
                    when {
                        incoming.fromPort == DiagramPort.TOP || incoming.toPort == DiagramPort.BOTTOM -> Cell(base.col, base.row - 1)
                        incoming.fromPort == DiagramPort.BOTTOM || incoming.toPort == DiagramPort.TOP -> Cell(base.col, base.row + 1)
                        incoming.fromPort == DiagramPort.LEFT || incoming.toPort == DiagramPort.RIGHT -> Cell(base.col - 1, base.row)
                        else -> Cell(base.col + 1, base.row)
                    }
                }
                outgoingEdge != null -> {
                    val base = grid.getValue(outgoingEdge.to)
                    when {
                        outgoingEdge.toPort == DiagramPort.BOTTOM || outgoingEdge.fromPort == DiagramPort.TOP -> Cell(base.col, base.row + 1)
                        outgoingEdge.toPort == DiagramPort.TOP || outgoingEdge.fromPort == DiagramPort.BOTTOM -> Cell(base.col, base.row - 1)
                        outgoingEdge.toPort == DiagramPort.RIGHT || outgoingEdge.fromPort == DiagramPort.LEFT -> Cell(base.col + 1, base.row)
                        else -> Cell(base.col - 1, base.row)
                    }
                }
                else -> Cell(0, (grid.values.maxOfOrNull { it.row } ?: -1) + 1)
            }
            place(node.id, suggested)
            pending.remove(node)
        }
        return grid
    }

    private fun starts(sizes: Map<Int, Float>, start: Float, gap: Float): Map<Int, Float> {
        var cursor = start
        return sizes.keys.sorted().associateWith { key -> cursor.also { cursor += sizes.getValue(key) + gap } }
    }

    internal fun label(node: DiagramNode) = DiagramText.layout(node.label, DiagramTextRole.LABEL, 210f)
    internal fun subLabel(node: DiagramNode) = DiagramText.layout(node.subLabel.orEmpty(), DiagramTextRole.SUB_LABEL, 210f)

    private fun sizeOf(node: DiagramNode): Size = when (node.shape) {
        DiagramNodeShape.MIXER, DiagramNodeShape.SUM -> Size(54f, 54f)
        DiagramNodeShape.JUNCTION -> Size(12f, 12f)
        else -> {
            val main = label(node)
            val sub = subLabel(node)
            val padding = if (node.shape == DiagramNodeShape.IO) 8f else 16f
            Size(maxOf(main.width, sub.width, if (node.shape == DiagramNodeShape.IO) 30f else 72f) + padding * 2,
                maxOf(main.height + sub.height + (if (sub.height > 0) 4f else 0f), 22f) + padding * 2)
        }
    }

    private fun resolvePort(port: DiagramPort, self: NodeBox, other: NodeBox): DiagramPort {
        if (port != DiagramPort.AUTO) return port
        val dx = other.centerX - self.centerX
        val dy = other.centerY - self.centerY
        return if (abs(dx) < 1f) {
            if (dy >= 0) DiagramPort.BOTTOM else DiagramPort.TOP
        } else if (dx >= 0) DiagramPort.RIGHT else DiagramPort.LEFT
    }

    private fun anchor(box: NodeBox, port: DiagramPort) = when (port) {
        DiagramPort.LEFT -> DiagramPoint(box.x, box.centerY)
        DiagramPort.RIGHT -> DiagramPoint(box.x + box.width, box.centerY)
        DiagramPort.TOP -> DiagramPoint(box.centerX, box.y)
        DiagramPort.BOTTOM -> DiagramPoint(box.centerX, box.y + box.height)
        DiagramPort.AUTO -> error("Unresolved port")
    }
}
