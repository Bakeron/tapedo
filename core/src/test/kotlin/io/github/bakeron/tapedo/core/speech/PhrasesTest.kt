package io.github.bakeron.tapedo.core.speech

import org.junit.Test
import org.junit.Assert.assertEquals

class PhrasesTest {

    @Test
    fun `a pause between phrases becomes a comma`() {
        assertEquals("Lista zakupów, marchewki, mleko i jajka", Phrases.join(listOf("Lista zakupów", "marchewki", "mleko i jajka")))
    }

    @Test
    fun `no second mark after a phrase that already ends with one`() {
        assertEquals("Lista zakupów: marchewki", Phrases.join(listOf("Lista zakupów:", "marchewki")))
        assertEquals("Kup mleko. Zadzwoń do mamy", Phrases.join(listOf("Kup mleko.", "Zadzwoń do mamy")))
    }

    @Test
    fun `blank phrases are skipped`() {
        assertEquals("marchewki", Phrases.join(listOf("", "  marchewki ", " ")))
        assertEquals("", Phrases.join(emptyList()))
    }
}
