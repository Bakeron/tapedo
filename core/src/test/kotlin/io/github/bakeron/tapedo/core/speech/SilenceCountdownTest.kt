package io.github.bakeron.tapedo.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceCountdownTest {

    @Test
    fun `pauses shorter than the setting do not end the recording`() {
        val c = SilenceCountdown(7_000)
        c.heard("Lista zakupów", 1_000)
        assertFalse(c.isOver(3_000)) // a 2 s pause before the next item
        c.heard("Lista zakupów, mleko", 3_000)
        assertFalse(c.isOver(9_999))
        assertTrue(c.isOver(10_000))
    }

    @Test
    fun `the same text again is not new words`() {
        val c = SilenceCountdown(7_000)
        c.heard("mleko", 1_000)
        c.heard("mleko", 6_000) // the recognizer repeats the last result
        assertTrue(c.isOver(8_000))
    }

    @Test
    fun `nothing said counts from the start`() {
        val c = SilenceCountdown(3_000)
        assertFalse(c.isOver(2_999))
        assertTrue(c.isOver(3_000))
    }
}
