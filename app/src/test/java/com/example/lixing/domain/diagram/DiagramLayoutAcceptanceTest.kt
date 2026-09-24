package com.example.lixing.domain.diagram

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DiagramLayoutAcceptanceTest {
    private fun node(id: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK) =
        DiagramNode(id = id, label = id, shape = shape)

    @Test fun `carrier listed first does not become the horizontal input chain`() {
        val result = DiagramLayout.layout(DiagramSpec("Title", listOf(node("carrier"), node("in"),
            node("mix", DiagramNodeShape.MIXER), node("out")), listOf(
            DiagramEdge("carrier", "mix", toPort = DiagramPort.BOTTOM),
            DiagramEdge("in", "mix"), DiagramEdge("mix", "out"))))
        val boxes = result.nodes.associateBy { it.node.id }
        assertEquals(boxes.getValue("in").centerY, boxes.getValue("out").centerY, .1f)
        assertEquals(boxes.getValue("mix").centerX, boxes.getValue("carrier").centerX, .1f)
        assertTrue(result.nodes.all { it.y > 40 })
    }

    @Test fun `feedback path avoids unrelated node interiors and keeps port directions`() {
        val result = DiagramLayout.layout(DiagramSpec("", listOf(node("a"), node("b"), node("c")), listOf(
            DiagramEdge("a", "b"), DiagramEdge("b", "c"),
            DiagramEdge("c", "a", fromPort = DiagramPort.BOTTOM, toPort = DiagramPort.BOTTOM))))
        val route = result.edges.last()
        assertTrue(route.points[1].y > route.start.y)
        assertTrue(route.points[route.points.lastIndex - 1].y > route.end.y)
        val box = result.nodes.single { it.node.id == "b" }
        route.points.zipWithNext().forEach { (p, q) ->
            val hits = if (p.x == q.x) p.x > box.x && p.x < box.x + box.width &&
                maxOf(p.y, q.y) > box.y && minOf(p.y, q.y) < box.y + box.height
            else p.y > box.y && p.y < box.y + box.height &&
                maxOf(p.x, q.x) > box.x && minOf(p.x, q.x) < box.x + box.width
            assertFalse("Feedback crosses the filter", hits)
        }
    }

    @Test fun `main chain is horizontal and carrier enters mixer from below without coordinates`() {
        val spec = DiagramSpec("", listOf(node("input", DiagramNodeShape.IO),
            node("mixer", DiagramNodeShape.MIXER), node("filter"), node("output", DiagramNodeShape.IO),
            node("carrier", DiagramNodeShape.IO)), listOf(
            DiagramEdge("input", "mixer"), DiagramEdge("mixer", "filter"),
            DiagramEdge("filter", "output"), DiagramEdge("carrier", "mixer", toPort = DiagramPort.BOTTOM)))
        val result = DiagramLayout.layout(spec)
        val boxes = result.nodes.associateBy { it.node.id }
        val mixer = boxes.getValue("mixer")
        listOf("input", "filter", "output").forEach {
            assertEquals("Main chain must be level: $it", mixer.centerY, boxes.getValue(it).centerY, 0.5f)
        }
        val carrier = boxes.getValue("carrier")
        assertTrue("Carrier must be below the mixer", carrier.y > mixer.y + mixer.height)
        assertEquals("Carrier branch must align with mixer", mixer.centerX, carrier.centerX, 0.5f)
    }

    @Test fun `default chain arrows enter target left edge`() {
        val result = DiagramLayout.layout(DiagramSpec("", listOf(node("a"), node("b")),
            listOf(DiagramEdge("a", "b"))))
        val target = result.nodes.single { it.node.id == "b" }
        val edge = result.edges.single()
        assertEquals(target.x, edge.end.x, 0.5f)
        assertEquals(target.centerY, edge.end.y, 0.5f)
    }

    @Test fun `parallel branches occupy separate visible boxes`() {
        val result = DiagramLayout.layout(DiagramSpec("", listOf(node("in"), node("a"), node("b"), node("out")),
            listOf(DiagramEdge("in", "a"), DiagramEdge("in", "b"),
                DiagramEdge("a", "out"), DiagramEdge("b", "out"))))
        result.nodes.forEachIndexed { index, a ->
            result.nodes.drop(index + 1).forEach { b ->
                val intersects = a.x < b.x + b.width && a.x + a.width > b.x &&
                    a.y < b.y + b.height && a.y + a.height > b.y
                assertTrue("Nodes overlap: ${a.node.id} and ${b.node.id}", !intersects)
            }
        }
    }

    @Test fun `textbook dual branch routes into signed summing circle`() {
        fun n(id: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK, row: Int, column: Int) =
            DiagramNode(id, id, shape, row = row, column = column)
        val spec = DiagramSpec("", listOf(
            n("in", DiagramNodeShape.IO, 0, 0), n("split", DiagramNodeShape.JUNCTION, 0, 1),
            n("mi", DiagramNodeShape.MIXER, 0, 2), n("mq", DiagramNodeShape.MIXER, 1, 2),
            n("fi", row = 0, column = 3), n("fq", row = 1, column = 3),
            n("hilbert", row = 1, column = 4), n("sum", DiagramNodeShape.SUM, 0, 5),
            n("out", DiagramNodeShape.IO, 0, 6), n("carrier", row = 2, column = 4),
            n("phase", row = 2, column = 1)), listOf(
            DiagramEdge("in", "split"), DiagramEdge("split", "mi", fromPort = DiagramPort.TOP),
            DiagramEdge("split", "mq", fromPort = DiagramPort.BOTTOM),
            DiagramEdge("mi", "fi"), DiagramEdge("fi", "sum", toPort = DiagramPort.TOP),
            DiagramEdge("mq", "fq"), DiagramEdge("fq", "hilbert"),
            DiagramEdge("hilbert", "sum", toPort = DiagramPort.BOTTOM, polarity = "−"),
            DiagramEdge("sum", "out"), DiagramEdge("carrier", "mi", toPort = DiagramPort.BOTTOM),
            DiagramEdge("carrier", "phase"), DiagramEdge("phase", "mq", toPort = DiagramPort.BOTTOM)))
        val result = DiagramLayout.layout(spec)
        assertEquals(2, result.edges.count { it.edge.to == "sum" })
        assertTrue(result.edges.filter { it.edge.to == "sum" }.all { it.endPort in setOf(DiagramPort.TOP, DiagramPort.BOTTOM) })
        assertTrue(result.nodes.single { it.node.id == "mq" }.row > result.nodes.single { it.node.id == "mi" }.row)
    }
}
