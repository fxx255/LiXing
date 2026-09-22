package com.example.lixing.data.assistant

import org.junit.Assert.*
import org.junit.Test

class AssistantFigureTest {
    private val diagram = """{"title":"D","nodes":[{"id":"a","label":"A"}],"edges":[]}"""
    private val plot = """{"title":"P","series":[{"expr":"x"}]}"""

    @Test fun `invalid figures retain numbering across types and continuation rounds`() {
        val first = AssistantResponseParser.parse("""{"reply":"first","plots":[{},$plot],"diagrams":[{},$diagram]}""")
        assertEquals(1, first.plots.size)
        assertEquals(1, first.diagrams.size)
        val slots = first.orderedFigures().toMutableList()
        assertEquals(4, slots.size)
        assertEquals(AssistantFigure.Missing, slots[0])
        assertTrue(slots[1] is AssistantFigure.Plot)
        assertEquals(AssistantFigure.Missing, slots[2])
        assertTrue(slots[3] is AssistantFigure.Diagram)
        val next = AssistantResponseParser.parse("""{"reply":"next","plots":[$plot]}""")
        assertEquals("[[FIGURE:5]]", offsetFigureAnchors("[[FIGURE:1]]", slots.size))
        slots += next.orderedFigures()
        assertTrue(slots[3] is AssistantFigure.Diagram)
        assertTrue(slots[4] is AssistantFigure.Plot)
    }

    @Test fun `truncated reply preserves failed slots and does not scan into the next array`() {
        val reply = AssistantResponseParser.parse("""{"plots":[{},$plot],"diagrams":[{},$diagram],"reply":"unfinished""")
        assertEquals(2, reply.plotSlots.size)
        assertEquals(2, reply.diagramSlots.size)
        assertNull(reply.plotSlots[0])
        assertNull(reply.diagramSlots[0])
        assertEquals("D", reply.diagrams.single().title)
    }
}
