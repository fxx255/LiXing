package com.example.lixing.domain

import com.example.lixing.ui.screen.plan.edit.EditableTimeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class EditableTimeParserTest {
    @Test
    fun `parses normal and full width input`() {
        assertEquals(LocalTime.of(21, 35), EditableTimeParser.parse("21:35"))
        assertEquals(LocalTime.of(22, 15), EditableTimeParser.parse("２２：１５"))
        assertEquals(LocalTime.of(21, 35), EditableTimeParser.parse(" 21 : 35 "))
    }

    @Test
    fun `rejects out of range time`() {
        assertNull(EditableTimeParser.parse("24:00"))
        assertNull(EditableTimeParser.parse("21:60"))
    }
}
