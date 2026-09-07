package com.example.lixing.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Documents the lifecycle contract: PAUSE + STOP for one background episode = one count. */
class FocusInterruptionTest {
    @Test
    fun `one background episode is counted once`() {
        var reported = false
        var interruptions = 0
        fun background() {
            if (!reported) {
                reported = true
                interruptions++
            }
        }
        background() // ON_PAUSE
        background() // ON_STOP
        assertEquals(1, interruptions)
        reported = false // ON_RESUME
        background()
        assertEquals(2, interruptions)
    }
}
