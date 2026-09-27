package com.example.lixing.domain.diagram

import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class IqDemodulatorLayoutTest {
    private fun node(id: String, role: String, shape: DiagramNodeShape = DiagramNodeShape.BLOCK,
                     label: String = id) = DiagramNode(id, label, shape, role = role)

    private fun receiver(qam: Boolean): DiagramSpec {
        val nodes = buildList {
            add(node("input", "input", DiagramNodeShape.IO))
            if (qam) add(node("agc", "agc"))
            add(node("split", "split", DiagramNodeShape.JUNCTION))
            add(node("mi", "i_mixer", DiagramNodeShape.MIXER))
            add(node("mq", "q_mixer", DiagramNodeShape.MIXER))
            add(node("fi", "i_filter", label = "I 路匹配滤波"))
            add(node("fq", "q_filter", label = "Q 路匹配滤波"))
            add(node("si", "i_sampler", label = "抽样"))
            add(node("sq", "q_sampler", label = "抽样"))
            add(node("di", "i_decision", label = if (qam) "四电平判决" else "二值判决"))
            add(node("dq", "q_decision", label = if (qam) "四电平判决" else "二值判决"))
            add(node("demap", "symbol_demapper", label = "符号解映射"))
            add(node("out", "output", DiagramNodeShape.IO))
            add(node("carrier", "local_oscillator"))
            add(node("phase", "phase_shift"))
            add(node("recovery", "carrier_recovery"))
            add(node("timing", "timing_recovery"))
        }
        val edges = buildList {
            if (qam) {
                add(DiagramEdge("input", "agc"))
                add(DiagramEdge("agc", "split"))
            } else add(DiagramEdge("input", "split"))
            add(DiagramEdge("split", "mi"))
            add(DiagramEdge("split", "mq"))
            listOf("mi" to "fi", "fi" to "si", "si" to "di", "di" to "demap",
                "mq" to "fq", "fq" to "sq", "sq" to "dq", "dq" to "demap",
                "demap" to "out", "carrier" to "mi", "carrier" to "phase",
                "phase" to "mq", "recovery" to "carrier", "timing" to "si",
                "timing" to "sq").forEach { (from, to) -> add(DiagramEdge(from, to)) }
            add(DiagramEdge("di", "recovery", dashed = true))
        }
        return DiagramSpec(if (qam) "16QAM 解调" else "QPSK 解调", nodes, edges,
            profile = DiagramLayoutProfile.IQ_DEMODULATOR)
    }

    @Test fun `QPSK aligns the two signal paths and separates shared control`() {
        val result = DiagramLayout.layout(receiver(false))
        val boxes = result.nodes.associateBy { it.node.id }
        listOf("mi" to "mq", "fi" to "fq", "si" to "sq", "di" to "dq").forEach { (i, q) ->
            assertEquals("$i and $q share a processing stage", boxes.getValue(i).centerX,
                boxes.getValue(q).centerX, 0.1f)
            assertTrue(boxes.getValue(i).centerY < boxes.getValue(q).centerY)
        }
        assertEquals(boxes.getValue("mi").centerX, boxes.getValue("carrier").centerX, 0.1f)
        assertEquals(boxes.getValue("mi").centerY, boxes.getValue("fi").centerY, 0.1f)
        assertEquals(boxes.getValue("mq").centerY, boxes.getValue("fq").centerY, 0.1f)
        assertTrue(boxes.getValue("timing").centerY > boxes.getValue("sq").centerY)
        assertTrue(boxes.getValue("demap").centerX > boxes.getValue("di").centerX)
        assertTrue(boxes.getValue("demap").centerY > boxes.getValue("di").centerY)
        assertTrue(boxes.getValue("demap").centerY < boxes.getValue("dq").centerY)
        assertGeometry(result)
    }

    @Test fun `16QAM adds stages without changing the I Q layout rules`() {
        val result = DiagramLayout.layout(receiver(true))
        val boxes = result.nodes.associateBy { it.node.id }
        assertTrue(boxes.getValue("agc").centerX < boxes.getValue("split").centerX)
        assertTrue(boxes.getValue("split").centerX < boxes.getValue("mi").centerX)
        assertEquals(boxes.getValue("di").centerX, boxes.getValue("dq").centerX, 0.1f)
        assertEquals(DiagramPort.TOP, result.edges.single { it.edge.from == "di" && it.edge.to == "demap" }.endPort)
        assertEquals(DiagramPort.BOTTOM, result.edges.single { it.edge.from == "dq" && it.edge.to == "demap" }.endPort)
        val feedback = result.edges.single { it.edge.dashed }
        assertEquals(DiagramPort.TOP, feedback.startPort)
        assertTrue(feedback.points.maxOf { it.y } > result.nodes.maxOf { it.y + it.height })
        assertGeometry(result)
    }

    @Test fun `signal stages follow edges when model lists nodes in reverse order`() {
        val spec = receiver(false)
        val normal = DiagramLayout.layout(spec).nodes.associateBy { it.node.id }
        val reversed = DiagramLayout.layout(spec.copy(nodes = spec.nodes.reversed()))
            .nodes.associateBy { it.node.id }
        listOf("split", "mi", "fi", "si", "di", "demap", "out").forEach { id ->
            assertEquals("$id stage depends on graph rather than JSON order",
                normal.getValue(id).centerX, reversed.getValue(id).centerX, 0.1f)
        }
    }

    @Test fun `parser accepts the shared profile and unknown profiles stay generic`() {
        val json = """{"title":"QPSK","profile":"iq_demodulator","nodes":[
            {"id":"i","label":"I","role":"i_decision"},
            {"id":"q","label":"Q","role":"q_decision"}],"edges":[]} """
        assertEquals(DiagramLayoutProfile.IQ_DEMODULATOR,
            DiagramParser.parseOne(Json.parseToJsonElement(json).jsonObject).profile)
        assertEquals(DiagramLayoutProfile.GENERIC,
            DiagramParser.parseOne(Json.parseToJsonElement(json.replace("iq_demodulator", "future_profile")).jsonObject).profile)
    }

    private fun assertGeometry(result: DiagramLayoutResult) {
        result.nodes.forEachIndexed { index, a ->
            result.nodes.drop(index + 1).forEach { b ->
                assertFalse("${a.node.id} overlaps ${b.node.id}",
                    a.x < b.x + b.width && a.x + a.width > b.x &&
                        a.y < b.y + b.height && a.y + a.height > b.y)
            }
        }
        result.edges.forEach { route ->
            route.points.forEach { point ->
                assertTrue("$point is outside the canvas", point.x in 0f..result.width &&
                    point.y in 0f..result.height)
            }
            route.points.zipWithNext().forEach { (a, b) ->
                assertTrue("${route.edge.from}->${route.edge.to} has a diagonal segment",
                    a.x == b.x || a.y == b.y)
                result.nodes.filter { it.node.id != route.edge.from && it.node.id != route.edge.to }
                    .forEach { box ->
                        val crosses = if (a.x == b.x) {
                            a.x > box.x && a.x < box.x + box.width &&
                                maxOf(a.y, b.y) > box.y && minOf(a.y, b.y) < box.y + box.height
                        } else {
                            a.y > box.y && a.y < box.y + box.height &&
                                maxOf(a.x, b.x) > box.x && minOf(a.x, b.x) < box.x + box.width
                        }
                        assertFalse("${route.edge.from}->${route.edge.to} crosses ${box.node.id}", crosses)
                    }
            }
        }
    }
}
