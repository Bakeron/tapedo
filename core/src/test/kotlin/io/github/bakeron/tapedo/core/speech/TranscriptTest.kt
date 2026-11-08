package io.github.bakeron.tapedo.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun `a phrase cut in two by the recognizer stays one item`() {
        // The bug from 07.11.2026: "Lista zakupów" said quickly came as "Lista" + "zakupów" -> a title and an item.
        val t = Transcript()
        t.partial("Lista", 0)
        t.phraseEnded("Lista", 300)
        t.partial("zakupów", 500)
        t.phraseEnded("zakupów", 900)
        assertEquals("Lista zakupów", t.text())
    }

    @Test
    fun `items said with pauses become separate even inside one recognizer phrase`() {
        // The bug from 07.11.2026: four items said with pauses came back as one phrase, so one list item.
        val t = Transcript()
        t.partial("klawiatura", 0)
        t.partial("klawiatura myszka", 2_500)
        t.partial("klawiatura myszka szklanka", 5_000)
        t.partial("klawiatura myszka szklanka wody", 5_300)
        t.partial("klawiatura myszka szklanka wody słoik", 8_000)
        t.partial("klawiatura myszka szklanka wody słoik cukru", 8_300)
        t.phraseEnded("klawiatura myszka szklanka wody słoik cukru", 9_000)
        assertEquals("klawiatura, myszka, szklanka wody, słoik cukru", t.text())
    }

    @Test
    fun `a pause shorter than two seconds is a space, two seconds or more a comma`() {
        val t = Transcript()
        t.phraseEnded("Monitor gigabajt", 0)
        t.phraseEnded("Monitor AGON", 1_999)
        t.phraseEnded("klawiatura", 3_999)
        assertEquals("Monitor gigabajt Monitor AGON, klawiatura", t.text())
    }

    @Test
    fun `a rewritten word does not count as a pause`() {
        val t = Transcript()
        t.partial("Monitor giga", 0)
        t.partial("Monitor gigabajt", 2_500) // same number of words, only corrected
        t.partial("Monitor gigabajt AGON", 3_000)
        assertEquals("Monitor gigabajt AGON", t.text())
    }

    @Test
    fun `a phrase ended with no final text keeps the words that were shown`() {
        val t = Transcript()
        t.partial("Monitor AGON", 0)
        t.phraseEnded(null, 500)
        t.phraseEnded("", 600)
        t.partial("klawiatura", 3_000)
        assertEquals("Monitor AGON, klawiatura", t.text())
    }

    @Test
    fun `a new phrase after a long pause without an end keeps the old one`() {
        // Some recognizers start the next phrase without sending the end of the previous one.
        val t = Transcript()
        t.partial("Monitor AGON", 0)
        t.partial("klawiatura", 2_500)
        assertEquals("Monitor AGON, klawiatura", t.text())
    }

    @Test
    fun `no comma after the recognizer's own punctuation`() {
        val t = Transcript()
        t.phraseEnded("Lista zakupów:", 0)
        t.phraseEnded("marchewki", 3_000)
        t.phraseEnded("Kup mleko.", 6_000)
        assertEquals("Lista zakupów: marchewki, Kup mleko.", t.text())
    }

    @Test
    fun `the phrase being spoken is part of the text`() {
        val t = Transcript()
        assertTrue(t.isEmpty())
        t.phraseEnded("mleko", 0)
        t.partial("chleb", 2_000)
        assertEquals("mleko, chleb", t.text())
    }
}
