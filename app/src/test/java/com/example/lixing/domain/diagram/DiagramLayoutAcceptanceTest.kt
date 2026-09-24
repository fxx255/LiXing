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

    @Test
    fun `textbook profile uses fixed rails and preserves empty stages`() {
        fun n(id: String, role: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK) =
            DiagramNode(id, id, shape, role = role)
        val spec = DiagramSpec(
            "SSB", listOf(
                n("in", "input", DiagramNodeShape.IO),
                n("split", "split", DiagramNodeShape.JUNCTION),
                n("upper", "upper_mixer", DiagramNodeShape.MIXER),
                n("lower", "lower_mixer", DiagramNodeShape.MIXER),
                n("uf", "upper_filter"),
                n("lf", "lower_filter"),
                n("sum", "sum", DiagramNodeShape.SUM),
                n("out", "output", DiagramNodeShape.IO),
            ),
            listOf(
                DiagramEdge("in", "split"),
                DiagramEdge("split", "upper", fromPort = DiagramPort.TOP),
                DiagramEdge("split", "lower", fromPort = DiagramPort.BOTTOM),
                DiagramEdge("upper", "uf"), DiagramEdge("lower", "lf"),
                DiagramEdge("uf", "sum", toPort = DiagramPort.TOP),
                DiagramEdge("lf", "sum", toPort = DiagramPort.BOTTOM),
                DiagramEdge("sum", "out"),
            ),
            profile = DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH,
        )
        val result = DiagramLayout.layout(spec)
        val boxes = result.nodes.associateBy { it.node.id }
        assertEquals(0, boxes.getValue("upper").row)
        assertEquals(2, boxes.getValue("lower").row)
        assertEquals(1, boxes.getValue("sum").row)
        assertEquals(2, boxes.getValue("upper").column)
        assertEquals(7, boxes.getValue("sum").column)
        // The profile keeps stage 3 visible even though this abbreviated
        // graph has no node there; collapsing it would make later wires
        // touch the filter boxes when a control branch is added.
        assertTrue(boxes.getValue("sum").x > boxes.getValue("uf").x + boxes.getValue("uf").width)
        result.nodes.forEachIndexed { index, a ->
            result.nodes.drop(index + 1).forEach { b ->
                val intersects = a.x < b.x + b.width && a.x + a.width > b.x &&
                    a.y < b.y + b.height && a.y + a.height > b.y
                assertFalse("Template nodes overlap: ${a.node.id} and ${b.node.id}", intersects)
            }
        }
    }

    @Test
    fun `textbook profile keeps duplicate roles visible and route bounds inside canvas`() {
        fun n(id: String, role: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK) =
            DiagramNode(id, id, shape, role = role)
        val spec = DiagramSpec(
            "", listOf(
                n("in", "input", DiagramNodeShape.IO),
                n("a", "upper_mixer", DiagramNodeShape.MIXER),
                n("b", "upper_mixer", DiagramNodeShape.MIXER),
            ),
            listOf(DiagramEdge("in", "a", label = "A"), DiagramEdge("a", "b", label = "B")),
            profile = DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH,
        )
        val result = DiagramLayout.layout(spec)
        assertEquals(3, result.nodes.map { it.node.id }.toSet().size)
        assertTrue(result.nodes.map { it.column }.distinct().size >= 2)
        result.edges.flatMap { it.points }.forEach { point ->
            assertTrue("route x outside canvas: $point", point.x in 0f..result.width)
            assertTrue("route y outside canvas: $point", point.y in 0f..result.height)
        }
        val maxLabelWidth = spec.edges.maxOf { DiagramText.layout(it.label.orEmpty(), DiagramTextRole.EDGE_LABEL, 140f).width }
        val maxRouteX = result.edges.flatMap { it.points }.maxOf { it.x }
        assertTrue("canvas must leave room for edge labels", result.width > maxRouteX + maxLabelWidth / 2f)
    }

    @Test
    fun `legacy split junction labelled A is not mistaken for test point A`() {
        val split = DiagramNode("split", "A", DiagramNodeShape.JUNCTION)
        val explicitTest = DiagramNode("probe", "A", DiagramNodeShape.JUNCTION, role = "test_a")
        val spec = DiagramSpec(
            "", listOf(split, explicitTest),
            listOf(DiagramEdge("split", "probe")),
            profile = DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH,
        )
        val result = DiagramLayout.layout(spec)
        val splitBox = result.nodes.single { it.node.id == "split" }
        val probeBox = result.nodes.single { it.node.id == "probe" }
        assertEquals(1, splitBox.column)
        assertEquals(1, probeBox.column)
        assertTrue(
            "legacy split and explicit A marker must not overlap",
            probeBox.x + probeBox.width <= splitBox.x ||
                splitBox.x + splitBox.width <= probeBox.x,
        )
    }

    @Test
    fun `textbook probes stay on signal wires and summer sits above the split`() {
        fun n(id: String, role: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK) =
            DiagramNode(id, id.uppercase(), shape, role = role)
        val spec = DiagramSpec("SSB", listOf(
            n("input", "input", DiagramNodeShape.IO),
            n("split", "split", DiagramNodeShape.JUNCTION),
            n("a", "test_a", DiagramNodeShape.JUNCTION),
            n("upper", "upper_mixer", DiagramNodeShape.MIXER),
            n("lower", "lower_mixer", DiagramNodeShape.MIXER),
            n("b", "test_b", DiagramNodeShape.JUNCTION),
            n("d", "test_d", DiagramNodeShape.JUNCTION),
            n("upper_filter", "upper_filter"),
            n("lower_filter", "lower_filter"),
            n("e", "test_e", DiagramNodeShape.JUNCTION),
            n("hilbert", "lower_hilbert"),
            n("c", "test_c", DiagramNodeShape.JUNCTION),
            n("f", "test_f", DiagramNodeShape.JUNCTION),
            n("carrier", "carrier"),
            n("phase", "phase_shift"),
            n("sum", "sum", DiagramNodeShape.SUM),
            n("g", "test_g", DiagramNodeShape.JUNCTION),
            n("output", "output", DiagramNodeShape.IO),
        ), listOf(
            DiagramEdge("input", "a"), DiagramEdge("a", "split"),
            DiagramEdge("split", "upper", fromPort = DiagramPort.TOP),
            DiagramEdge("split", "lower", fromPort = DiagramPort.BOTTOM),
            DiagramEdge("upper", "b"), DiagramEdge("b", "upper_filter"),
            DiagramEdge("upper_filter", "c"), DiagramEdge("c", "sum", toPort = DiagramPort.TOP),
            DiagramEdge("lower", "d"), DiagramEdge("d", "lower_filter"),
            DiagramEdge("lower_filter", "e"), DiagramEdge("e", "hilbert"),
            DiagramEdge("hilbert", "f"), DiagramEdge("f", "sum", toPort = DiagramPort.BOTTOM),
            DiagramEdge("carrier", "upper", toPort = DiagramPort.BOTTOM),
            DiagramEdge("carrier", "phase"),
            DiagramEdge("phase", "lower", toPort = DiagramPort.BOTTOM),
            DiagramEdge("sum", "g"), DiagramEdge("g", "output"),
        ), profile = DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH)
        val result = DiagramLayout.layout(spec)
        val box = result.nodes.associateBy { it.node.id }
        fun between(point: String, left: String, right: String) {
            assertTrue("$point must be after $left", box.getValue(point).x >
                box.getValue(left).x + box.getValue(left).width)
            assertTrue("$point must be before $right", box.getValue(point).x +
                box.getValue(point).width < box.getValue(right).x)
        }
        between("a", "input", "split")
        between("b", "upper", "upper_filter")
        between("d", "lower", "lower_filter")
        between("e", "lower_filter", "hilbert")
        between("g", "sum", "output")
        assertEquals(box.getValue("upper").centerY, box.getValue("b").centerY, .1f)
        assertEquals(box.getValue("lower").centerY, box.getValue("d").centerY, .1f)
        assertEquals(box.getValue("lower").centerY, box.getValue("e").centerY, .1f)
        assertEquals(box.getValue("sum").centerY, box.getValue("g").centerY, .1f)
        assertEquals(box.getValue("sum").centerX, box.getValue("f").centerX, .1f)
        assertTrue(box.getValue("upper").centerY < box.getValue("sum").centerY)
        assertTrue(box.getValue("sum").centerY < box.getValue("split").centerY)
        assertTrue(box.getValue("split").centerY < box.getValue("lower").centerY)
        assertTrue(box.getValue("f").centerY > box.getValue("sum").centerY)
        assertTrue(box.getValue("f").centerY < box.getValue("lower").centerY)
        assertEquals(DiagramPort.TOP, result.edges.single { it.edge.from == "phase" }.endPort)
        assertEquals(DiagramPort.BOTTOM, result.edges.single { it.edge.to == "f" }.endPort)
        assertEquals(DiagramPort.TOP, result.edges.single { it.edge.from == "f" }.startPort)
    }
}
